

package com.github.qcalculator.core.utils;
import com.github.qcalculator.core.model.common.DiscountGroup;
import com.github.qcalculator.core.model.common.DiscountWrapper;
import com.github.qcalculator.core.model.common.Item;
import com.github.qcalculator.core.permutation.Permutation;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AtomicLongMap;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.tuple.Pair;
import java.util.*;
import java.util.stream.Collectors;


public class DiscountGroupUtil {

    private static final String EXCLUDE = "exclude";

    /**
     * 返回所有共享组
     * Pair的left是长度不超过 Permutation.SUPPORTEDSIZE 的内容，right是第 Permutation.SUPPORTEDSIZE+1
     * 种优惠即其他优惠（若有），即不参与全排列的优惠
     * @param groups
     * @param inMap
     * @return
     */
    public static List<Pair<Set<DiscountWrapper>, Set<DiscountWrapper>>> transform(List<List<DiscountGroup>> groups, Map<String, Map<String, DiscountWrapper>> inMap) {
        List<Pair<Set<DiscountWrapper>, Set<DiscountWrapper>>> resultList = Lists.newArrayList();
        List<List<Item>> list = mergeGroups(groups, inMap);
        if (CollectionUtils.isEmpty(list)) {
            return Lists.newArrayList();
        }
        for (List<Item> items : list) {
            Set<DiscountWrapper> discountWrappers = items.stream().map(x -> {
                if (inMap.containsKey(x.getType())) {
                    Map<String, DiscountWrapper> m = inMap.get(x.getType());
                    if (m.containsKey(x.getId())) {
                        // 根据item的引用, 从inMap中拿出真正的DiscountWrapper对象
                        // 同样的, 一个items对应一种计算组合, 也就形成了一个Set
                        return m.get(x.getId());
                    }
                }
                return null;
            }).filter(Objects::nonNull).collect(Collectors.toSet());

            if (CollectionUtils.isNotEmpty(discountWrappers) && discountWrappers.size() >= 2) {
                if (discountWrappers.size() > Permutation.SUPPORTEDSIZE) {
                    // 一组优惠中超过7个策略了, 我们就优先保障mustUse的策略
                    resultList.add(balanceLR(discountWrappers));
                } else {
                    // ≤7个, 进Permutation做全排列，重点保障的计算；
                    // 注意此处做Pair是将left和right组合, left是小于等于7的部分, right是大于7的部分
                    resultList.add(Pair.of(discountWrappers, Sets.newHashSet()));
                }
            }
        }
        //优先计算短的共享组
        return resultList.stream().sorted(Comparator.comparing(x -> x.getLeft().size(), Collections.reverseOrder())).collect(Collectors.toList());
    }

    /**
     * 保证mustuse的优惠进入left集合
     * @param discountWrappers 当前可用优惠
     */
    private static Pair<Set<DiscountWrapper>, Set<DiscountWrapper>> balanceLR(Set<DiscountWrapper> discountWrappers) {
        Set<DiscountWrapper> left = Sets.newHashSet();
        Set<DiscountWrapper> right = Sets.newHashSet();
        for (DiscountWrapper wrapper : discountWrappers) {
            if (wrapper.isMustUse()) {
                // 优先保障必须使用的优惠策略
                left.add(wrapper);
            } else {
                right.add(wrapper);
            }
        }
        if (left.size() < Permutation.SUPPORTEDSIZE) {
            // mustuse不足7个, 就从right中再补一些进来
            int c = Permutation.SUPPORTEDSIZE - left.size();
            Iterator<DiscountWrapper> it = right.iterator();
            while (c > 0 && it.hasNext()) {
                DiscountWrapper w = it.next();
                left.add(w);
                it.remove();
                c--;
            }
        } else if (left.size() > Permutation.SUPPORTEDSIZE) {
            // mustuse超过7个了, 再删一点, 最终使得left恰好为7个策略
            int c = left.size() - Permutation.SUPPORTEDSIZE;
            Iterator<DiscountWrapper> it = left.iterator();
            while (c > 0 && it.hasNext()) {
                DiscountWrapper w = it.next();
                right.add(w);
                it.remove();
                c--;
            }
        }
        return Pair.of(left, right);
    }

    /**
     * 合并规则转化为共享组
     * @param groups 所有规则大组
     * @param inMap 实际可用优惠
     */
    private static List<List<Item>> mergeGroups(List<List<DiscountGroup>> groups, Map<String, Map<String, DiscountWrapper>> inMap) {
        if (CollectionUtils.isEmpty(groups) || MapUtils.isEmpty(inMap)) {
            return null;
        }
        //resultList 接收最终的结果：每个元素是一个 List<Item>，即一个"共享组"
        //组内 Item 两两可叠加（互斥的组合已在展开时被拆到不同组）；引擎对每个组独立全排列，跨组取全局最优
        List<List<Item>> resultList = Lists.newArrayList();
        //ctx 全局计数器，对于独立共享组多次被使用进行删除（唯一需要去重的场景）
        AtomicLongMap<String> ctx = AtomicLongMap.create();
        //索引，存放可能需要删除的key在resultList中的下标
        Map<String, Integer> idxMap = Maps.newHashMap();
        for (List<DiscountGroup> list : groups) {
            mergeGroup(list, inMap, ctx, idxMap, resultList);
        }
        Map<String, Long> map = ctx.asMap();
        List<Integer> orderedList = Lists.newArrayList();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            // key是mergeGroup生成的uniqueKey, value是对应的出现次数
            Integer idx = idxMap.get(e.getKey());
            if (Objects.nonNull(idx) && e.getValue() > 1) {
                // 出现次数大于1, 表示重复了, 放入orderedList
                orderedList.add(idx);
            }
        }
        orderedList.sort(Collections.reverseOrder());
        //从后往前删除，否则索引会出问题
        for (Integer i : orderedList) {
            resultList.remove(i.intValue());
        }
        return resultList.stream().filter(CollectionUtils::isNotEmpty).collect(Collectors.toList());
    }

    private static void mergeGroup(List<DiscountGroup> groups, Map<String, Map<String, DiscountWrapper>> inMap,
                                              AtomicLongMap<String> ctx, Map<String, Integer> idxMap,
                                              List<List<Item>> resultList) {
        if (CollectionUtils.isEmpty(groups)) {
            return;
        }
        // groups是List<DiscountGroup>, README中规定groups内部只有1个或者2个DiscountGroup对象
        // 先获取第一个基底列表, 这里是与用户可用优惠进行了交集过滤
        List<Item> xList = groups.get(0).filterItems(inMap);
        if (CollectionUtils.isEmpty(xList)) {
            return;
        }
        if (groups.size() == 1) {
            //必然是共享组, 组内优惠互相可叠加
            //存入全局上下文，去重时使用，这里累计次数，若次数大于1，需要在最外层移除此共享组
            String key = uniqueKey(xList);
            // 记录当前list的使用次数
            ctx.incrementAndGet(key);
            //记录索引,以便删除
            idxMap.put(key, resultList.size());
            resultList.add(xList);
        } else {
            // 这里表示groups中有两个组

            //yList必然是互斥的, 这里是与用户可用优惠进行了交集过滤
            List<Item> yList = groups.get(1).filterItems(inMap);
            if (Objects.equals(EXCLUDE, groups.get(0).getRelation())) {
                // 第一组xlist也是EXCLUDE的情况
                // 产品设计规避了重复的情况，无需去重
                // 做笛卡尔积
                for (Item item : xList) {
                    for (Item item1 : yList) {
                        resultList.add(Lists.newArrayList(item, item1));
                    }
                }
            } else {
                // 第一组xlist是SHARE的情况
                // 存入全局上下文，去重时使用，这里累计次数，若次数大于1，需要在最外层移除此共享组
                String k = uniqueKey(xList);
                for (Item item : yList) {
                    // 由于第一组组内是共享的, 所以第二组的策略直接加入原有resultList各个元素的末尾
                    ctx.incrementAndGet(k);
                    List<Item> xCopy = Lists.newArrayList(xList);
                    xCopy.add(item);
                    // 组内可叠加：xCopy = 共享组基底 + 一个互斥项, 构成一个新的候选共享组
                    // (yList 各项彼此互斥，所以每个 y 要单独复制一份基底成组)
                    resultList.add(xCopy);
                }
            }
        }
    }

    private static String uniqueKey(List<Item> list) {
//        map后 -> ["zhekou1", "manajian2"]
//        sorted后 -> ["manjian2", "zhekou1"]
//        joining后 -> "manjian2zhekou1"
        return list.stream().map(i -> i.getType() + i.getId()).sorted().collect(Collectors.joining());
    }

}
